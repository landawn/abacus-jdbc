/*
 * Copyright (c) 2025, Haiyang Li.
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.jdbc.SQLTransaction.CreatedBy;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.JSONParser;
import com.landawn.abacus.parser.KryoParser;
import com.landawn.abacus.parser.ParserFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn.BiConsumers;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.QueryUtil;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLMapper;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.TriConsumer;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 * The JdbcContext class provides various utility methods and configurations for JDBC operations.
 * It includes methods for managing SQL logging, transactions, and DAO creation.
 * 
 * @see {@link com.landawn.abacus.jdbc.JdbcUtil}
 * @see {@link com.landawn.abacus.jdbc.JdbcUtils}
 */
public class JdbcContext {

    static final Logger logger = LoggerFactory.getLogger(JdbcContext.class);

    static final Logger sqlLogger = LoggerFactory.getLogger("com.landawn.abacus.SQL");

    static final JSONParser jsonParser = ParserFactory.createJSONParser();
    static final KryoParser kryoParser = ParserFactory.isKryoAvailable() ? ParserFactory.createKryoParser() : null;
    public static final int DEFAULT_BATCH_SIZE = 200;
    public static final int DEFAULT_FETCH_SIZE_FOR_BIG_RESULT = 1000;
    public static final int DEFAULT_FETCH_SIZE_FOR_STREAM = 100;
    public static final int DEFAULT_CACHE_CAPACITY = 1000;
    /**
     * Default cache evict delay in milliseconds
     */
    public static final int DEFAULT_CACHE_EVICT_DELAY = 3 * 1000;
    /**
     * Default cache live time in milliseconds.
     */
    public static final int DEFAULT_CACHE_LIVE_TIME = 30 * 60 * 1000;
    /**
     * Default cache idle time in milliseconds
     */
    public static final int DEFAULT_CACHE_MAX_IDLE_TIME = 3 * 60 * 1000;

    static final Set<String> QUERY_METHOD_NAME_SET = N.asSet("query", "queryFor", "list", "get", "batchGet", "find", "findFirst", "findOnlyOne", "exist",
            "notExist", "count");

    static final Set<String> UPDATE_METHOD_NAME_SET = N.asSet("update", "delete", "deleteById", "insert", "save", "batchUpdate", "batchDelete",
            "batchDeleteByIds", "batchInsert", "batchSave", "batchUpsert", "upsert", "execute");

    static final Set<Method> BUILT_IN_DAO_QUERY_METHODS = StreamEx.of(ClassUtil.getClassesByPackage(Dao.class.getPackageName(), false, true)) //
            .filter(Dao.class::isAssignableFrom)
            .flattMap(Class::getDeclaredMethods)
            .filter(it -> Modifier.isPublic(it.getModifiers()) && !Modifier.isStatic(it.getModifiers()))
            .filter(it -> it.getAnnotation(NonDBOperation.class) == null)
            .filter(it -> N.anyMatch(QUERY_METHOD_NAME_SET, e -> Strings.containsIgnoreCase(it.getName(), e)))
            .toImmutableSet();

    static final Set<Method> BUILT_IN_DAO_UPDATE_METHODS = StreamEx.of(ClassUtil.getClassesByPackage(Dao.class.getPackageName(), false, true)) //
            .filter(Dao.class::isAssignableFrom)
            .flattMap(Class::getDeclaredMethods)
            .filter(it -> Modifier.isPublic(it.getModifiers()) && !Modifier.isStatic(it.getModifiers()))
            .filter(it -> it.getAnnotation(NonDBOperation.class) == null)
            .filter(it -> N.anyMatch(UPDATE_METHOD_NAME_SET, e -> Strings.containsIgnoreCase(it.getName(), e)))
            .toImmutableSet();

    static final Predicate<Method> IS_QUERY_METHOD = method -> N.anyMatch(QUERY_METHOD_NAME_SET,
            it -> Strings.isNotEmpty(it) && (Strings.startsWith(method.getName(), it) || Pattern.matches(it, method.getName())));

    static final Predicate<Method> IS_UPDATE_METHOD = method -> N.anyMatch(UPDATE_METHOD_NAME_SET,
            it -> Strings.isNotEmpty(it) && (Strings.startsWith(method.getName(), it) || Pattern.matches(it, method.getName())));

    public static final int DEFAULT_MAX_SQL_LOG_LENGTH = 1024;
    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG = 1000L;
    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG = 3000L;

    public static final Throwables.Function<Statement, String, SQLException> DEFAULT_SQL_EXTRACTOR = stmt -> {
        Statement stmtToUse = stmt;
        String clsName = stmtToUse.getClass().getName();

        if ((clsName.startsWith("com.zaxxer.hikari") || clsName.startsWith("com.mchange.v2.c3p0")) && stmt.isWrapperFor(Statement.class)) {
            stmtToUse = stmt.unwrap(Statement.class);
            clsName = stmtToUse.getClass().getName();
        }

        if (clsName.startsWith("oracle.jdbc") && (stmtToUse instanceof oracle.jdbc.internal.OraclePreparedStatement)) { //NOSONAR
            try {
                return ((oracle.jdbc.internal.OraclePreparedStatement) stmtToUse).getOriginalSql();
            } catch (final SQLException e) {
                // ignore.
            }
        }

        return stmtToUse.toString();
    };

    static Throwables.Function<Statement, String, SQLException> _sqlExtractor = DEFAULT_SQL_EXTRACTOR; //NOSONAR

    static final ThreadLocal<SqlLogConfig> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> new SqlLogConfig(false, DEFAULT_MAX_SQL_LOG_LENGTH));

    static final ThreadLocal<SqlLogConfig> minExecutionTimeForSqlPerfLog_TL = ThreadLocal
            .withInitial(() -> new SqlLogConfig(DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG, DEFAULT_MAX_SQL_LOG_LENGTH));

    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    static boolean isSqlLogAllowed = true;
    static boolean isSqlPerfLogAllowed = true;
    static boolean isDaoMethodPerfLogAllowed = true;

    static boolean isInSpring = true;

    static {
        try {
            isInSpring = ClassUtil.forClass("org.springframework.datasource.DataSourceUtils") != null;
        } catch (final Throwable e) {
            isInSpring = false;
        }
    }

    static TriConsumer<String, Long, Long> _sqlLogHandler = null; //NOSONAR

    /**
     * Turns off SQL logging globally.
     * This method sets the flag to disable SQL logging across the entire application.
     */
    public static void turnOffSqlLogGlobally() {
        isSqlLogAllowed = false;
    }

    /**
     * Turns off SQL performance logging globally.
     * This method sets the flag to disable SQL performance logging across the entire application.
     */
    public static void turnOffSqlPerfLogGlobally() {
        isSqlPerfLogAllowed = false;
    }

    /**
     * Turns off DAO method performance logging globally.
     * This method sets the flag to disable DAO method performance logging across the entire application.
     */
    public static void turnOffDaoMethodPerfLogGlobally() {
        isDaoMethodPerfLogAllowed = false;
    }

    /**
     * Enables/Disables SQL logging in the current thread.
     *
     * @param b {@code true} to enable SQL logging, {@code false} to disable it.
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    static void enableSqlLog(final boolean b) {
        enableSqlLog(b, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enables/Disables SQL logging in the current thread.
     *
     * @param b {@code true} to enable SQL logging, {@code false} to disable it.
     * @param maxSqlLogLength The maximum length of the SQL log. Default value is 1024.
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    static void enableSqlLog(final boolean b, final int maxSqlLogLength) {
        final SqlLogConfig config = isSQLLogEnabled_TL.get();
        // synchronized (isSQLLogEnabled_TL) {
        if (logger.isDebugEnabled() && config.isEnabled != b) {
            if (b) {
                logger.debug("Turn on [SQL] log");
            } else {
                logger.debug("Turn off [SQL] log");
            }
        }

        config.set(b, maxSqlLogLength);
        // }
    }

    /**
     * Enables SQL logging in the current thread.
     * This method enables SQL logging with the default maximum SQL log length.
     */
    public static void enableSqlLog() {
        enableSqlLog(DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enables SQL logging in the current thread with a specified maximum SQL log length.
     *
     * @param maxSqlLogLength The maximum length of the SQL log. Default value is 1024.
     */
    public static void enableSqlLog(final int maxSqlLogLength) {
        enableSqlLog(true, maxSqlLogLength);
    }

    /**
     * Disables SQL logging in the current thread.
     * This method disables SQL logging while retaining the current maximum SQL log length.
     */
    public static void disableSqlLog() {
        enableSqlLog(false, isSQLLogEnabled_TL.get().maxSqlLogLength);
    }

    /**
     * Checks if SQL logging is enabled in the current thread.
     *
     * @return {@code true} if SQL logging is enabled, otherwise {@code false}.
     */
    public static boolean isSqlLogEnabled() {
        return isSQLLogEnabled_TL.get().isEnabled;
    }

    static void logSql(final String sql) {
        if (!isSqlLogAllowed || !sqlLogger.isDebugEnabled()) {
            return;
        }

        final SqlLogConfig sqlLogConfig = isSQLLogEnabled_TL.get();

        if (sqlLogConfig.isEnabled) {
            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.debug(Strings.concat("[SQL]: ", sql));
            } else {
                sqlLogger.debug(Strings.concat("[SQL]: " + Strings.abbreviate(sql, sqlLogConfig.maxSqlLogLength)));
            }
        }
    }

    static void handleSqlLog(final Statement stmt, final SqlLogConfig sqlLogConfig, final long startTime) throws SQLException {
        final long endTime = System.currentTimeMillis();
        final long elapsedTime = endTime - startTime;
        String sql = null;

        final Throwables.Function<Statement, String, SQLException> sqlExtractor = N.defaultIfNull(JdbcContext._sqlExtractor, JdbcContext.DEFAULT_SQL_EXTRACTOR);

        if (isSqlPerfLogAllowed && sqlLogger.isInfoEnabled() && elapsedTime >= sqlLogConfig.minExecutionTimeForSqlPerfLog) {
            sql = sqlExtractor.apply(stmt);

            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", sql));
            } else {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", Strings.abbreviate(sql, sqlLogConfig.maxSqlLogLength)));
            }
        }

        final TriConsumer<String, Long, Long> sqlLogHandler = _sqlLogHandler;

        if (sqlLogHandler != null) {
            if (sql == null) {
                sql = sqlExtractor.apply(stmt);
            }

            sqlLogHandler.accept(sql, startTime, endTime);
        }
    }

    static boolean isToHandleSqlLog(final SqlLogConfig sqlLogConfig) {
        return _sqlLogHandler != null || (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled());
    }

    /**
     * Retrieves the current SQL extractor function.
     * This function is used to extract SQL statements from a given Statement object.
     *
     * @return The current SQL extractor function.
     */
    public static Throwables.Function<Statement, String, SQLException> getSqlExtractor() {
        return JdbcContext._sqlExtractor;
    }

    /**
     * Sets the SQL extractor function.
     * This function is used to extract SQL statements from a given Statement object.
     *
     * @param sqlExtractor The SQL extractor function to set.
     */
    public static void setSqlExtractor(final Throwables.Function<Statement, String, SQLException> sqlExtractor) {
        JdbcContext._sqlExtractor = sqlExtractor;
    }

    /**
     * Retrieves the SQL log handler.
     * The SQL log handler is a TriConsumer that handles SQL log messages along with their execution times.
     *
     * @return The current SQL log handler.
     */
    public static TriConsumer<String, Long, Long> getSqlLogHandler() {
        return _sqlLogHandler;
    }

    /**
     * Sets the SQL log handler.
     * The SQL log handler is a TriConsumer that handles SQL log messages along with their execution times.
     *
     * @param sqlLogHandler 1st parameter is the SQL statement,
     *                      2nd parameter is the start time of SQL execution,
     *                      3rd parameter is the end time of SQL execution.
     */
    public static void setSqlLogHandler(final TriConsumer<String, Long, Long> sqlLogHandler) {
        _sqlLogHandler = sqlLogHandler;
    }

    /**
     * Sets the minimum execution time to log SQL performance in the current thread.
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds
     *                                      for logging SQL performance.
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog) {
        setMinExecutionTimeForSqlPerfLog(minExecutionTimeForSqlPerfLog, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Sets the minimum execution time to log SQL performance in the current thread.
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds
     *                                      for logging SQL performance. Default value is 1000 (milliseconds).
     * @param maxSqlLogLength the maximum length of the SQL log. Default value is 1024.
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        final SqlLogConfig config = minExecutionTimeForSqlPerfLog_TL.get();
        // synchronized (minExecutionTimeForSqlPerfLog_TL) {
        if (logger.isDebugEnabled() && config.minExecutionTimeForSqlPerfLog != minExecutionTimeForSqlPerfLog) {
            if (minExecutionTimeForSqlPerfLog >= 0) {
                logger.debug("set 'minExecutionTimeForSqlPerfLog' to: " + minExecutionTimeForSqlPerfLog);
            } else {
                logger.debug("Turn off SQL performance log");
            }
        }

        config.set(minExecutionTimeForSqlPerfLog, maxSqlLogLength);
        // }
    }

    /**
     * Returns the minimum execution time in milliseconds to log SQL performance in the current thread.
     * The default value is 1000 milliseconds.
     *
     * @return the minimum execution time for logging SQL performance in milliseconds.
     */
    public static long getMinExecutionTimeForSqlPerfLog() {
        return minExecutionTimeForSqlPerfLog_TL.get().minExecutionTimeForSqlPerfLog;
    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    /**
     * Since enable/disable sql log flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <E>
     * @param sqlAction
     * @throws E
     */
    public static <E extends Exception> void runWithSqlLogDisabled(final Throwables.Runnable<E> sqlAction) throws E {
        if (isSqlLogEnabled()) {
            disableSqlLog();

            try {
                sqlAction.run();
            } finally {
                enableSqlLog();
            }
        } else {
            sqlAction.run();
        }
    }

    /**
     * Since enable/disable sql log flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <R>
     * @param <E>
     * @param sqlAction
     * @return
     * @throws E
     */
    public static <R, E extends Exception> R callWithSqlLogDisabled(final Throwables.Callable<R, E> sqlAction) throws E {
        if (isSqlLogEnabled()) {
            disableSqlLog();

            try {
                return sqlAction.call();
            } finally {
                enableSqlLog();
            }
        } else {
            return sqlAction.call();
        }
    }

    /**
     * Retrieves a connection from the specified DataSource.
     * If Spring transaction management is enabled and a transaction is active,
     * it will return the connection associated with the current transaction.
     * Otherwise, it will return a new connection from the DataSource.
     *
     * @param ds The DataSource from which to retrieve the connection.
     * @return A Connection object that represents a connection to the database.
     * @throws UncheckedSQLException If a SQL exception occurs while retrieving the connection.
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws UncheckedSQLException {
        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;

                try {
                    return ds.getConnection();
                } catch (final SQLException e1) {
                    throw new UncheckedSQLException(e1);
                }
            }
        } else {
            try {
                return ds.getConnection();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Releases the given connection back to the DataSource.
     * If Spring transaction management is enabled and a transaction is active,
     * it will release the connection associated with the current transaction.
     * Otherwise, it will close the connection directly.
     *
     * @param conn The Connection to be released.
     * @param ds The DataSource from which the connection was obtained.
     */
    @SuppressWarnings("deprecation")
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Checks if there is an active transaction for the given DataSource in current thread.
     *
     * @param ds The DataSource to check for an active transaction.
     * @return {@code true} if there is an active transaction, {@code false} otherwise.
     */
    public static boolean isInTransaction(final javax.sql.DataSource ds) {
        if (SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL) != null) {
            return true;
        }

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            Connection conn = null;

            try {
                conn = JdbcContext.getConnection(ds);

                return org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(conn, ds);
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;
            } finally {
                JdbcContext.releaseConnection(conn, ds);
            }
        }

        return false;
    }

    /**
     * Begins a new transaction for the given DataSource.
     *
     * @param dataSource The DataSource for which to begin the transaction.
     * @return A SQLTransaction object representing the new transaction.
     * @throws UncheckedSQLException If a SQL exception occurs while beginning the transaction.
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource) throws UncheckedSQLException {
        return beginTransaction(dataSource, IsolationLevel.DEFAULT);
    }

    /**
     * Begins a new transaction for the given DataSource with the specified isolation level.
     *
     * @param dataSource The DataSource for which to begin the transaction.
     * @param isolationLevel The isolation level for the transaction.
     * @return A SQLTransaction object representing the new transaction.
     * @throws UncheckedSQLException If a SQL exception occurs while beginning the transaction.
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel) throws UncheckedSQLException {
        return beginTransaction(dataSource, isolationLevel, false);
    }

    /**
     * Starts a global transaction which will be shared by all in-line database query with the same {@code DataSource} in the same thread,
     * including methods: {@code JdbcContext.beginTransaction/prepareQuery/prepareNamedQuery/prepareCallableQuery, SQLExecutor(Mapper).beginTransaction/get/insert/batchInsert/update/batchUpdate/query/list/findFirst/...}
     *
     * <br />
     * Spring Transaction is supported and Integrated.
     * If this method is called at where a Spring transaction is started with the specified {@code DataSource},
     * the {@code Connection} started the Spring Transaction will be used here.
     * That's to say the Spring transaction will have the final control on commit/roll back over the {@code Connection}.
     *
     * <br />
     * <br />
     *
     * Here is the general code pattern to work with {@code SQLTransaction}.
     *
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcContext.beginTransaction(dataSource1, isolation);
     *
     *     try {
     *         ...
     *         doSomethingB(); // Share the same transaction 'tranA' because they're in the same thread and start transaction with same DataSource 'dataSource1'.
     *         ...
     *         doSomethingC(); // won't share the same transaction 'tranA' although they're in the same thread but start transaction with different DataSource 'dataSource2'.
     *         ...
     *         tranA.commit();
     *     } finally {
     *         tranA.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingB() {
     *     ...
     *     final SQLTransaction tranB = JdbcContext.beginTransaction(dataSource1, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranB.commit();
     *     } finally {
     *         tranB.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingC() {
     *     ...
     *     final SQLTransaction tranC = JdbcContext.beginTransaction(dataSource2, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranC.commit();
     *     } finally {
     *         tranC.rollbackIfNotCommitted();
     *     }
     * }
     * </pre>
     * </code>
     *
     * It's incorrect to use flag to identity the transaction should be committed or rolled back.
     * Don't write below code:
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcContext.beginTransaction(dataSource1, isolation);
     *     boolean flagToCommit = false;
     *     try {
     *         // do your work with the conn...
     *         ...
     *         flagToCommit = true;
     *     } finally {
     *         if (flagToCommit) {
     *             tranA.commit();
     *         } else {
     *             tranA.rollbackIfNotCommitted();
     *         }
     *     }
     * }
     * </code>
     * </pre>
     *
     * @param dataSource
     * @param isolationLevel
     * @param isForUpdateOnly
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link JdbcContext#getConnection(javax.sql.DataSource)}
     * @see {@link JdbcContext#releaseConnection(Connection, javax.sql.DataSource)}
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel, final boolean isForUpdateOnly)
            throws UncheckedSQLException {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(isolationLevel, cs.isolationLevel);

        SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try { //NOSONAR
                conn = JdbcContext.getConnection(dataSource);
                tran = new SQLTransaction(dataSource, conn, isolationLevel, CreatedBy.JDBC_UTIL, true); //NOSONAR
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (!noException) {
                    JdbcContext.releaseConnection(conn, dataSource);
                }
            }

            logger.info("Create a new SQLTransaction(id={})", tran.id());
            SQLTransaction.putTransaction(tran);
        } else {
            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
            tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);
        }

        return tran;
    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    /**
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);
        T result = null;

        try {
            result = cmd.call();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Function<Connection, T, E> cmd)
            throws E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);
        T result = null;

        try {
            result = cmd.apply(tran.connection());
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);

        try {
            cmd.run();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Consumer<Connection, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);

        try {
            cmd.accept(tran.connection());
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    return cmd.call();
                } else {
                    return tran.callNotInMe(cmd);
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.call();
            } else {
                return tran.callNotInMe(cmd);
            }
        }
    }

    /**
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Function<javax.sql.DataSource, T, E> cmd) throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    return cmd.apply(dataSource);
                } else {
                    return tran.callNotInMe(() -> cmd.apply(dataSource));
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.apply(dataSource);
            } else {
                return tran.callNotInMe(() -> cmd.apply(dataSource));
            }
        }
    }

    /**
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    cmd.run();
                } else {
                    tran.runNotInMe(cmd);
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.run();
            } else {
                tran.runNotInMe(cmd);
            }
        }
    }

    /**
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    cmd.accept(dataSource);
                } else {
                    tran.runNotInMe(() -> cmd.accept(dataSource));
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.accept(dataSource);
            } else {
                tran.runNotInMe(() -> cmd.accept(dataSource));
            }
        }
    }

    /**
     * Since using or not using Spring transaction flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <E>
     * @param sqlAction
     * @throws E
     */
    public static <E extends Exception> void runWithoutUsingSpringTransaction(final Throwables.Runnable<E> sqlAction) throws E {
        if (isSpringTransactionalNotUsed()) {
            sqlAction.run();
        } else {
            doNotUseSpringTransactional(true);

            try {
                sqlAction.run();
            } finally {
                doNotUseSpringTransactional(false);
            }
        }
    }

    /**
     * Since using or not using Spring transaction flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <R>
     * @param <E>
     * @param sqlAction
     * @return
     * @throws E
     */
    public static <R, E extends Exception> R callWithoutUsingSpringTransaction(final Throwables.Callable<R, E> sqlAction) throws E {
        if (isSpringTransactionalNotUsed()) {
            return sqlAction.call();
        } else {
            doNotUseSpringTransactional(true);

            try {
                return sqlAction.call();
            } finally {
                doNotUseSpringTransactional(false);
            }
        }
    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    /**
     * Don't share {@code Spring Transactional} in the current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to not share, {@code false} to share it again.
     */
    static void doNotUseSpringTransactional(final boolean b) {
        // synchronized (isSpringTransactionalDisabled_TL) {
        if (isInSpring) {
            if (logger.isWarnEnabled() && isSpringTransactionalDisabled_TL.get() != b) { //NOSONAR
                if (b) {
                    logger.warn("Disable Spring Transactional");
                } else {
                    logger.warn("Enable Spring Transactional again");
                }
            }

            isSpringTransactionalDisabled_TL.set(b);
        } else {
            logger.warn("Not in Spring or not able to retrieve Spring Transactional");
        }
        // }
    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    /**
     * Check if {@code Spring Transactional} is shared or not in the current thread.
     *
     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
     */
    static boolean isSpringTransactionalNotUsed() {
        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Tuple2<Class<?>, Class<?>>, Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer> noIdGeneratorGetterSetter = Tuple
            .of(JdbcUtil.NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null, BiConsumers.doNothing());

    @SuppressWarnings("rawtypes")
    private static final Map<Class<? extends Dao>, BiRowMapper<?>> idExtractorPool = new ConcurrentHashMap<>();

    @SuppressWarnings({ "rawtypes", "deprecation", "null" })
    static <ID> Tuple3<BiRowMapper<ID>, com.landawn.abacus.util.function.Function<Object, ID>, com.landawn.abacus.util.function.BiConsumer<ID, Object>> getIdGeneratorGetterSetter(
            final Class<? extends Dao> daoInterface, final Class<?> entityClass, final NamingPolicy namingPolicy, final Class<?> idType) {
        if (!ClassUtil.isBeanClass(entityClass)) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        final Tuple2<Class<?>, Class<?>> key = Tuple.of(entityClass, idType);

        Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>> map = idGeneratorGetterSetterPool
                .get(key);

        if (map == null) {
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(entityClass);
            final boolean isNoId = N.isEmpty(idPropNameList) || QueryUtil.isFakeId(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final BeanInfo entityInfo = isNoId ? null : ParserUtil.getBeanInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = !isNoId && idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);
            final BeanInfo idBeanInfo = ClassUtil.isBeanClass(idType) ? ParserUtil.getBeanInfo(idType) : null;

            final com.landawn.abacus.util.function.Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? idPropInfo::getPropValue //
                            : (isEntityId ? entity -> {
                                final Seid ret = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (final PropInfo propInfo : idPropInfoList) {
                                    ret.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            } : entity -> {
                                final Object ret = idBeanInfo.createBeanResult();

                                for (final PropInfo propInfo : idPropInfoList) {
                                    ClassUtil.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) idBeanInfo.finishBeanResult(ret);
                            }));

            final com.landawn.abacus.util.function.BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (isEntityId ? (id, entity) -> {
                                if (id instanceof final EntityId entityId) {
                                    PropInfo propInfo = null;

                                    for (final String propName : entityId.keySet()) {
                                        propInfo = entityInfo.getPropInfo(propName);

                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null) {
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                        }
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            } : (id, entity) -> {
                                if (id != null && ClassUtil.isBeanClass(id.getClass())) {
                                    @SuppressWarnings("UnnecessaryLocalVariable")
                                    final Object entityId = id;

                                    for (final PropInfo propInfo : idPropInfoList) {
                                        propInfo.setPropValue(entity, ClassUtil.getPropValue(entityId, propInfo.name));
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            }));

            map = new EnumMap<>(NamingPolicy.class);

            for (final NamingPolicy np : NamingPolicy.values()) {
                final ImmutableMap<String, String> propColumnNameMap = QueryUtil.getProp2ColumnNameMap(entityClass, namingPolicy);

                final ImmutableMap<String, String> columnPropNameMap = EntryStream.of(propColumnNameMap)
                        .inversed()
                        .flatmapKey(e -> N.asList(e, e.toLowerCase(), e.toUpperCase()))
                        .distinctByKey()
                        .toImmutableMap();

                final BiRowMapper<Object> keyExtractor = isNoId ? noIdGeneratorGetterSetter._1
                        : (idExtractorPool.containsKey(daoInterface) ? (BiRowMapper<Object>) idExtractorPool.get(daoInterface) //
                                : (isOneId ? (rs, columnLabels) -> idPropInfo.dbType.get(rs, 1) //
                                        : (rs, columnLabels) -> {
                                            if (columnLabels.size() == 1) {
                                                return idPropInfo.dbType.get(rs, 1);
                                            } else if (isEntityId) {
                                                final int columnCount = columnLabels.size();
                                                final Seid id = Seid.of(ClassUtil.getSimpleClassName(entityClass));
                                                String columnName = null;
                                                String propName = null;
                                                PropInfo propInfo = null;

                                                for (int i = 0; i < columnCount; i++) {
                                                    columnName = columnLabels.get(i);

                                                    if ((propName = columnPropNameMap.get(columnName)) == null
                                                            || (propInfo = entityInfo.getPropInfo(propName)) == null) {
                                                        id.set(columnName, JdbcUtil.getColumnValue(rs, i + 1));
                                                    } else {
                                                        id.set(propInfo.name, propInfo.dbType.get(rs, i + 1));
                                                    }
                                                }

                                                return id;
                                            } else {
                                                final List<Tuple2<String, PropInfo>> tpList = Stream.of(columnLabels)
                                                        .filter(it -> idBeanInfo.getPropInfo(it) != null)
                                                        .map(it -> Tuple.of(it, idBeanInfo.getPropInfo(it)))
                                                        .toList();
                                                final Object id = idBeanInfo.createBeanResult();

                                                for (final Tuple2<String, PropInfo> tp : tpList) {
                                                    tp._2.setPropValue(id, tp._2.dbType.get(rs, tp._1));
                                                }

                                                return idBeanInfo.finishBeanResult(id);
                                            }
                                        }));

                map.put(np, Tuple.of(keyExtractor, idGetter, idSetter));
            }

            idGeneratorGetterSetterPool.put(key, map);
        }

        return (Tuple3) map.get(namingPolicy);
    }

    /**
     * Sets the ID extractor for the specified DAO interface.
     *
     * @param <T> The type of the entity.
     * @param <ID> The type of the ID.
     * @param <SB> The type of the SQLBuilder.
     * @param <TD> The type of the CrudDao.
     * @param daoInterface The DAO interface class.
     * @param idExtractor The RowMapper used to extract the ID.
     * @throws IllegalArgumentException If the daoInterface or idExtractor is invalid.
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final RowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

        idExtractorPool.put(daoInterface, (rs, cls) -> idExtractor.apply(rs));
    }

    /**
     * Sets the ID extractor for the specified DAO interface.
     *
     * @param <T> The type of the entity.
     * @param <ID> The type of the ID.
     * @param <SB> The type of the SQLBuilder.
     * @param <TD> The type of the CrudDao.
     * @param daoInterface The DAO interface class.
     * @param idExtractor The RowMapper used to extract the ID.
     * @throws IllegalArgumentException If the daoInterface or idExtractor is invalid.
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final BiRowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

        idExtractorPool.put(daoInterface, idExtractor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) {
        return createDao(daoInterface, ds, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper) {
        return createDao(daoInterface, ds, sqlMapper, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param daoCache It's better to not share cache between Dao instances.
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Jdbc.DaoCache daoCache) {
        return createDao(daoInterface, ds, sqlMapper, daoCache, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final Executor executor) {
        return createDao(daoInterface, ds, null, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Executor executor) {
        return createDao(daoInterface, ds, sqlMapper, null, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param daoCache It's better to not share cache between Dao instances.
     * @param executor
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Jdbc.DaoCache daoCache, final Executor executor) {

        //    synchronized (dsEntityDaoPool) {
        //        @SuppressWarnings("rawtypes")
        //        Map<Class<?>, Dao> entityDaoPool = dsEntityDaoPool.get(ds);
        //
        //        if (entityDaoPool == null) {
        //            entityDaoPool = new HashMap<>();
        //            dsEntityDaoPool.put(ds, entityDaoPool);
        //        }
        //
        //        entityDaoPool.put(getTargetEntityClass(daoInterface), dao);
        //    }

        return DaoImpl.createDao(daoInterface, null, ds, sqlMapper, daoCache, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds) {
        return createDao(daoInterface, targetTableName, ds, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @param daoCache It's better to not share cache between Dao instances.
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Jdbc.DaoCache daoCache) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, daoCache, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final Executor executor) {
        return createDao(daoInterface, targetTableName, ds, null, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Executor executor) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, null, executor);
    }

    /**
     *
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @param cache It's better to not share cache between Dao instances.
     * @param executor
     * @return
     * @throws IllegalArgumentException
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Jdbc.DaoCache cache, final Executor executor) throws IllegalArgumentException {

        //    synchronized (dsEntityDaoPool) {
        //        @SuppressWarnings("rawtypes")
        //        Map<Class<?>, Dao> entityDaoPool = dsEntityDaoPool.get(ds);
        //
        //        if (entityDaoPool == null) {
        //            entityDaoPool = new HashMap<>();
        //            dsEntityDaoPool.put(ds, entityDaoPool);
        //        }
        //
        //        entityDaoPool.put(getTargetEntityClass(daoInterface), dao);
        //    }

        return DaoImpl.createDao(daoInterface, targetTableName, ds, sqlMapper, cache, executor);
    }

    static final String CACHE_KEY_SPLITOR = "#";

    @SuppressWarnings("unused")
    static String createCacheKey(final String tableName, final String fullClassMethodName, final Object[] args, final Logger daoLogger) {
        String paramKey = null;

        if (kryoParser != null) {
            try {
                paramKey = kryoParser.serialize(args);
            } catch (final Exception e) {
                // ignore;
                daoLogger.warn("Failed to generated cache key and not able cache the result for method: " + fullClassMethodName);
            }
        } else {
            final List<Object> newArgs = Stream.of(args).map(it -> {
                if (it == null) {
                    return null;
                }

                final Type<?> type = N.typeOf(it.getClass());

                if (type.isSerializable() || type.isCollection() || type.isMap() || type.isArray() || type.isBean() || type.isEntityId()) {
                    return it;
                } else {
                    return it.toString();
                }
            }).toList();

            try {
                paramKey = N.toJson(newArgs);
            } catch (final Exception e) {
                // ignore;
                daoLogger.warn("Failed to generated cache key and not able cache the result for method: " + fullClassMethodName);
            }
        }

        return Strings.concat(fullClassMethodName, CACHE_KEY_SPLITOR, tableName, CACHE_KEY_SPLITOR, paramKey);
    }

    static final ThreadLocal<Jdbc.DaoCache> localThreadCache_TL = new ThreadLocal<>();

    /**
     * Enables the cache for Dao queries in the current thread.
     *
     * <pre>
     * <code>
     * JdbcContext.startDaoCacheOnCurrentThread();
     * try {
     *    // your code here
     * } finally {
     *   JdbcContext.closeDaoCacheOnCurrentThread();
     * }
     *
     * </code>
     * </pre>
     * 
     * @return the created {@code DaoCache} for current thread.
     * @see Jdbc.DaoCache#createByMap()
     * @see Jdbc.DaoCache#createByMap(Map)
     */
    public static Jdbc.DaoCache startDaoCacheOnCurrentThread() {
        final Jdbc.DaoCache localThreadCache = Jdbc.DaoCache.createByMap();

        return startDaoCacheOnCurrentThread(localThreadCache);
    }

    /**
     * Enables the cache for Dao queries in the current thread.
     *
     * <pre>
     * <code>
     * JdbcContext.startDaoCacheOnCurrentThread(localThreadCache);
     * try {
     *    // your code here
     * } finally {
     *   JdbcContext.closeDaoCacheOnCurrentThread();
     * }
     *
     * </code>
     * </pre>
     * @param localThreadCache
     * @return the specified localThreadCache
     * @see Jdbc.DaoCache#createByMap()
     * @see Jdbc.DaoCache#createByMap(Map)
     */
    public static Jdbc.DaoCache startDaoCacheOnCurrentThread(final Jdbc.DaoCache localThreadCache) {
        localThreadCache_TL.set(localThreadCache);

        return localThreadCache;
    }

    /**
     * Closes the cache for Dao queries in the current thread.
     */
    public static void closeDaoCacheOnCurrentThread() {
        localThreadCache_TL.remove();
    }

}
